/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 877 §2.5 — the one place agent-tool arguments are parsed, coerced and bounded.
 *
 * <p>It replaces five per-tool {@code ObjectMapper} fields and two private, mutually inconsistent
 * accessors. The inconsistency was not cosmetic: {@code SearchTool} read {@code limit} with
 * {@code asInt}, which coerces the string {@code "5"} that small local models routinely emit, while
 * {@code ReadDocumentTool} required {@code isNumber()} — so {@code offset_chars: "3000"} fell back
 * to the default and silently restarted a paged read at page 0. Two answers to one question is the
 * definition of a split authority; this is the single answer.
 *
 * <p><b>Coerce, but never guess.</b> A numeric string becomes a number. A value that is not a
 * number in any reading raises {@link BadArgument}, which the tool turns into a
 * {@code BAD_REQUEST} failure naming the field — loud, and self-correcting for the model. Falling
 * back to a default on garbage is what made the read bug invisible.
 *
 * <p><b>Bounds semantics</b> reproduce every existing call site exactly rather than being tidied: a
 * value ABOVE {@code max} clamps to {@code max}; a value BELOW {@code min} is treated as unusable
 * and yields the {@code fallback} (not {@code min}). That is what {@code SearchTool}'s
 * {@code if (limit < 1) limit = DEFAULT_LIMIT} and {@code BrowseTool}'s
 * {@code if (maxFolders < 1) maxFolders = DEFAULT_MAX_FOLDERS} already did, and what
 * {@code ReadDocumentTool}'s {@code requested <= 0 ? READ_PAGE_CHARS : min(requested, …)} already
 * did. Absent, JSON-null and blank all yield the fallback.
 *
 * <p>The exactness claim is scoped to the BOUNDS. One arm diverges deliberately: a present,
 * non-numeric value. {@code SearchTool} read {@code limit} with {@code asInt(DEFAULT_LIMIT)}, so
 * {@code {"limit":"lots"}} silently became 3; here it raises {@link BadArgument} and the tool
 * answers {@code BAD_REQUEST} naming the field. That is the "loud, not silent" rule above applied
 * to the one case where it changes an observable answer — the model can fix a named bad argument,
 * and cannot fix a default it was never told it got.
 */
public final class ToolArgs {

  private ToolArgs() {}

  /** The one JSON mapper for the agent-tool package. */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Thrown when an argument is present but cannot be read as its declared type. Distinct from a
   * malformed-JSON failure so {@code AgentToolErrors} can classify both as {@code BAD_REQUEST}
   * without conflating "the model sent invalid JSON" with "the model sent a valid but wrong value".
   */
  public static final class BadArgument extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public BadArgument(String message) {
      super(message);
    }
  }

  /** Parse a tool's arguments JSON. Throws Jackson's own exception on malformed input. */
  public static JsonNode parse(String argumentsJson) {
    return MAPPER.readTree(argumentsJson);
  }

  /**
   * An integer argument, accepting a JSON number or a numeric string.
   *
   * @param fallback returned when the key is absent, JSON-null, blank, or below {@code min}
   * @param min values below this are unusable and yield {@code fallback}
   * @param max values above this clamp to {@code max}
   * @throws BadArgument when the value is present and non-blank but is not a number
   */
  public static int intArg(JsonNode args, String field, int fallback, int min, int max) {
    JsonNode node = args == null ? null : args.get(field);
    if (node == null || node.isNull()) {
      return fallback;
    }
    int value;
    if (node.isIntegralNumber()) {
      value = node.asInt(fallback);
    } else if (node.isNumber()) {
      // Tempdoc 878 — a non-integral number is refused on the SAME terms as its stringified twin.
      // Silently flooring 3000.9 while refusing "3000.9" would make the refusal message ("must be a
      // whole number") false in one of the two cases, which is a small version of the exact defect
      // this method exists to remove.
      throw new BadArgument(refusal(field, node));
    } else if (node.isString()) {
      String raw = node.asString().strip();
      if (raw.isEmpty()) {
        return fallback;
      }
      try {
        value = Integer.parseInt(raw);
      } catch (NumberFormatException e) {
        throw new BadArgument(refusal(field, node));
      }
    } else {
      throw new BadArgument(refusal(field, node));
    }
    if (value > max) {
      return max;
    }
    return value < min ? fallback : value;
  }

  /** How much of an unusable argument is echoed back — enough to recognise, not enough to carry. */
  private static final int BAD_ARG_ECHO_CHARS = 120;

  /**
   * Tempdoc 878's refusal wording, folded in here at its request. It names the field AND the
   * offending value, because a model that cannot see what it sent cannot correct it — but the value
   * is BOUNDED: a model that passes a large object where an int belongs would otherwise get an
   * error message the size of that object, and an error is not a channel for returning content.
   */
  private static String refusal(String field, JsonNode node) {
    String offending = String.valueOf(node);
    if (offending.length() > BAD_ARG_ECHO_CHARS) {
      offending = offending.substring(0, BAD_ARG_ECHO_CHARS) + "…";
    }
    return field
        + " must be a whole number, but was "
        + offending
        + ". Call the tool again with "
        + field
        + " as a number, e.g. \""
        + field
        + "\": 3000.";
  }

  /**
   * A boolean argument. Absent, JSON-null and unreadable values are {@code false} — the shape both
   * {@code BrowseTool}'s {@code list_files} and {@code SearchTool}'s pipeline flags already used.
   */
  public static boolean boolArg(JsonNode args, String field) {
    JsonNode node = args == null ? null : args.get(field);
    return node != null && !node.isNull() && node.asBoolean(false);
  }

  /**
   * A string argument, or {@code null} when absent or JSON-null. Does not strip and does not
   * blank-check: callers differ on whether a blank string is an error (search's {@code query}) or a
   * no-op (browse's {@code parent_path}), and folding that in would put a second decision in one
   * helper.
   */
  public static String stringArg(JsonNode args, String field) {
    JsonNode node = args == null ? null : args.get(field);
    return (node == null || node.isNull()) ? null : node.asText();
  }

  /** {@link #stringArg} with a default for the absent/JSON-null case. */
  public static String stringArg(JsonNode args, String field, String fallback) {
    String value = stringArg(args, field);
    return value == null ? fallback : value;
  }
}
