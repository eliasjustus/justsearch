/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.core.util.ContextBudget;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Tool-message context compression for the agent loop (tempdoc 240 W2 — extracted
 * from {@code AgentLoopService}). Keeps the agent's conversation within the token
 * budget by compressing older tool results:
 *
 * <ul>
 *   <li>{@link #truncate(String)} — Layer-2 hard cut at {@link #toolResultCapChars()};
 *   <li>{@link #stripSearchExcerpts(String)} — drops the longest per-result field;
 *   <li>{@link #compressToolMessages(List)} — Layer-3: compresses all but the last
 *       {@code keepLastResults} tool messages each iteration.
 * </ul>
 */
final class AgentContextCompressor {

  /**
   * Tempdoc 865 §7.5 — THE RECEIPT: which tool calls' results still put text in front of the model
   * in the prompt this pass produced, and which have had their text removed. Keyed by {@code
   * tool_call_id}, which is what makes the join to grounding sources possible at all: compression
   * copies the message map and replaces only {@code content} ({@link #compressToolMessages}), so the
   * link from a compressed message back to its tool call — and thence to the sources that call
   * minted — survives untouched.
   *
   * <p><b>THREE outcomes, not two, and the third is why this record has two sets rather than one
   * flag.</b> A tool call is reported {@code textIntact} when its message still holds a carrier line
   * ({@link ToolResultCarrier#carriesText}), {@code textRemoved} when this pass rewrote it or it
   * bears {@link #COMPRESSED_MARKER}, and named in NEITHER set otherwise. That last case is a real
   * "cannot tell": a tool whose output never carried hit text looks exactly like one whose text was
   * stripped by a pass this receipt did not witness. Collapsing it into "removed" is the bug this
   * shape prevents — a dense-only search hit is written as {@code Preview:}, never {@code Excerpt:},
   * and a one-set receipt reported every such source as never sent while its text sat in the prompt.
   *
   * <p>{@code textIntact} is recomputed from scratch every pass, so it is a picture of ONE prompt.
   * {@code textRemoved} is monotone by construction — a message's content is only ever shortened, so
   * a carrier line that is gone never returns — which is why {@code AgentSession} may safely carry it
   * forward. The per-final-prompt property lives in the CARRIER SET instead: a document re-returned
   * by a later search has a new, intact carrier.
   *
   * @param textIntact tool calls whose message still carries a hit-text line
   * @param textRemoved tool calls whose message demonstrably no longer does
   */
  record CompressionReceipt(Set<String> textIntact, Set<String> textRemoved) {

    /** No pass has run, so nothing is known about any prompt. Consumers must say nothing. */
    static final CompressionReceipt NONE = new CompressionReceipt(Set.of(), Set.of());

    CompressionReceipt {
      textIntact = Set.copyOf(textIntact);
      textRemoved = Set.copyOf(textRemoved);
    }

    /** True when this receipt describes a real prompt (at least one tool message was classified). */
    boolean observed() {
      return !textIntact.isEmpty() || !textRemoved.isEmpty();
    }
  }

  /** The marker {@link #compressToolOutput} stamps on every output it actually rewrote. */
  static final String COMPRESSED_MARKER = "[compressed-tool-output";

  private static final Set<String> COMPRESSION_KEYWORDS =
      Set.of("error", "warning", "failed", "result", "path", "match", "id");

  private final boolean enabled;
  private final int minChars;
  private final int keepLastResults;

  /**
   * The live per-call budget (tempdoc 883 decision 3). The Layer-2 cap used to be a
   * {@code static final} resolved at class-init, so neither a window change nor a config change
   * after the first tool call ever reached it; it is now read per truncation.
   */
  private final Supplier<ContextBudget> budget;

  AgentContextCompressor(boolean enabled, int minChars, int keepLastResults) {
    this(enabled, minChars, keepLastResults, () -> AgentContextBudgets.forCall(null));
  }

  AgentContextCompressor(
      boolean enabled, int minChars, int keepLastResults, Supplier<ContextBudget> budget) {
    this.enabled = enabled;
    this.minChars = minChars;
    this.keepLastResults = keepLastResults;
    this.budget = budget;
  }

  /** The Layer-2 per-tool-result cap for the CURRENT window and config. */
  int toolResultCapChars() {
    return AgentContextBudgets.toolResultCapChars(budget.get());
  }

  /** Layer-2: hard-truncate a single tool result that exceeds the per-result cap. */
  String truncate(String output) {
    int cap = toolResultCapChars();
    if (output == null || output.length() <= cap) {
      return output;
    }
    return output.substring(0, cap)
        + "\n[... truncated, " + (output.length() - cap) + " chars omitted]";
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
      return receiptFor(messages, Set.of());
    }

    List<Integer> toolMessageIndexes = new ArrayList<>();
    for (int i = 0; i < messages.size(); i++) {
      Object role = messages.get(i).get("role");
      if ("tool".equals(role)) {
        toolMessageIndexes.add(i);
      }
    }

    if (toolMessageIndexes.size() <= keepLastResults) {
      return receiptFor(messages, Set.of());
    }

    var rewritten = new LinkedHashSet<String>();
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
      if (message.get("tool_call_id") instanceof String id && !id.isBlank()) {
        rewritten.add(id);
      }
    }
    return receiptFor(messages, rewritten);
  }

  /**
   * Tempdoc 865 §7.5 — read the receipt off the message list this pass produced.
   *
   * <p>Two sources of truth, and each covers the other's blind spot. {@link
   * ToolResultCarrier#carriesText} asks the ARTIFACT whether a hit's text is still in front of the
   * model — the only question that matters, and one no bookkeeping trail can get wrong. But its
   * negative answer is ambiguous, because a tool that never carried hit text is indistinguishable
   * from one whose text was stripped. So "removed" needs positive evidence: this pass rewrote the
   * message, or the message bears {@link #COMPRESSED_MARKER} from a pass that did.
   *
   * <p>Neither signal alone is sufficient, and both failure modes are real. Artifact-only reported a
   * dense-only {@code Preview:} message as stripped with zero compression. Rewrite-only would miss
   * every message compressed in an EARLIER pass, because {@link #compressToolOutput} refuses to
   * re-compress its own output and the marker is the only trace left — and it misses the strip-only
   * case entirely (a message whose excerpts are removed but whose remainder falls under {@code
   * minChars} is written back with no marker), which is what {@code AgentSession} carries forward
   * across passes rather than re-deriving here.
   *
   * <p>Anything neither intact nor evidenced-removed is named in NEITHER set. Say nothing.
   */
  static CompressionReceipt receiptFor(
      List<Map<String, Object>> messages, Set<String> rewrittenThisPass) {
    if (messages == null || messages.isEmpty()) {
      return CompressionReceipt.NONE;
    }
    var intact = new LinkedHashSet<String>();
    var removed = new LinkedHashSet<String>();
    for (Map<String, Object> message : messages) {
      if (!"tool".equals(message.get("role"))
          || !(message.get("tool_call_id") instanceof String id)
          || id.isBlank()) {
        continue;
      }
      String content = message.get("content") instanceof String s ? s : "";
      if (ToolResultCarrier.carriesText(content)) {
        // A carrier line survived, so this call's text is (at least partly) in the prompt. This wins
        // over the rewrite evidence: compression's line selection can keep a `Preview:` line, and
        // "some of it is still there" forbids the only claim this producer makes.
        intact.add(id);
      } else if (rewrittenThisPass.contains(id) || content.startsWith(COMPRESSED_MARKER)) {
        removed.add(id);
      }
    }
    return new CompressionReceipt(intact, removed);
  }

  /**
   * Search-specific compression: strips excerpt lines and read pages from a tool result. They are
   * the longest per-result fields and are only useful for the iteration that produced them.
   *
   * <p>Tempdoc 865 §7.5 — {@code STRIPPABLE_LINE}, not {@code CARRIER_LINE}. Preview lines are a
   * dense-only hit's whole text and were never part of Layer 3; widening the strip to match the
   * reader's pattern would silently delete them from the prompt.
   *
   * <p><b>Tempdoc 878 §D.2 — this SUBSTITUTES; it used to DELETE.</b> The removed lines are replaced
   * by one {@link ToolResultCarrier#elidedLine} scar naming how much went. Deleting outright left a
   * read result as a bare {@code [read] … More: offset_chars=N} header: an instruction to fetch the
   * next page, sitting on a message whose page had just been removed, with nothing on the artifact
   * saying so. Layer 2 marks its cut and {@link #compressToolOutput} marks its own; this was the one
   * elision in the loop that left the model no way to know it had happened.
   *
   * <p>Idempotent by construction: the scar matches neither pattern in {@link ToolResultCarrier}, so
   * a second pass finds nothing strippable and adds no second scar.
   *
   * <p>HONEST LIMIT: when the stripped remainder still clears {@code minChars}, {@link
   * #compressToolOutput} runs over this output and its keep-budget can clip the scar's tail. The
   * elision stays marked in that case — {@link #COMPRESSED_MARKER} is itself a mark, and it is what
   * the receipt reads — but the scar's behaviour-shaping second half may not survive verbatim. Two
   * marks, of which one may be clipped, is the trade for keeping the strip honest as a function
   * rather than deferring its mark until after a later, unrelated pass.
   */
  static String stripSearchExcerpts(String content) {
    if (!ToolResultCarrier.mayHaveStrippableLine(content)) {
      return content;
    }
    var matcher = ToolResultCarrier.STRIPPABLE_LINE.matcher(content);
    int lines = 0;
    int chars = 0;
    while (matcher.find()) {
      lines++;
      // +1 for the line's own newline: `.*$` stops before it, but the strip removes it too, and a
      // scar that under-reports what it removed is a small version of the defect it exists to fix.
      chars += matcher.end() - matcher.start() + 1;
    }
    if (lines == 0) {
      return content;
    }
    String stripped =
        ToolResultCarrier.STRIPPABLE_LINE.matcher(content).replaceAll("").replaceAll("\n{2,}", "\n");
    String separator = stripped.isEmpty() || stripped.endsWith("\n") ? "" : "\n";
    return stripped + separator + ToolResultCarrier.elidedLine(lines, chars);
  }

  private String compressToolOutput(String content) {
    if (content == null
        || content.length() < minChars
        || content.startsWith(COMPRESSED_MARKER)) {
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
            COMPRESSED_MARKER + " originalChars=%d keptChars=%d]%n%s",
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
}
