/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.intent;

import io.justsearch.agent.api.registry.Intent;
import io.justsearch.agent.api.registry.IntentExtractor;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.ShellAddress;
import io.justsearch.agent.api.registry.StateSnapshot;
import io.justsearch.agent.api.registry.SurfaceRef;
import io.justsearch.agent.api.registry.TransportTag;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Pure-function {@link IntentExtractor} that converts a raw text payload (Markdown
 * or plain prose containing {@code justsearch://} URLs) into a stream of
 * {@link Intent} envelopes.
 *
 * <p>Per tempdoc 487 §4.2 + §6 step 10: Java port of
 * {@code modules/ui-web/src/shell-v0/router/parser.ts}'s {@code extractUrls} +
 * {@code parseUrl}. Both implementations are governed by the slice-487 §5
 * cross-language fixture corpus
 * ({@code scripts/ci/url-grammar-fixtures/v1.json}); the
 * {@code MarkdownUrlExtractorConformanceTest} test enforces equivalence.
 *
 * <p>Syntactic only: the parser checks scheme, host, pathname structure, id
 * regex, and percent-encoding. Semantic validation (id resolves against the
 * live catalog; args match destination's inputSchema) is the
 * {@code BackendIntentRouter}'s job — keeps the extractor pure-function-shaped
 * and reusable across any text-stream intent source (LLM chat emission, future
 * agent-loop reasoning narration, future plugin chat output).
 *
 * <p>Transport tag is supplied at construction time; production wiring uses
 * {@link TransportTag#LLM_EMISSION} for the slice 487 NavigateChatShape
 * consumer. Other consumers (future) supply their own transport.
 */
public final class MarkdownUrlExtractor implements IntentExtractor<String> {

  /** Stable id; referenced by IntentSource registrations. */
  public static final String ID = "core.markdown-url";

  /**
   * Matches a {@code justsearch://...} URL terminated by whitespace,
   * closing paren/bracket, angle bracket, or quote. Mirrors {@code parser.ts}.
   */
  private static final Pattern URL_PATTERN =
      Pattern.compile("justsearch://[^\\s)\\]<>\"'`]+");

  /**
   * Strips trailing prose punctuation from extracted URLs. Mirrors
   * {@code parser.ts}'s {@code trailingPunctRe}. The pattern matches U+201D
   * (right double quotation mark) deliberately — prose commonly ends URLs
   * with curly quotes.
   */
  private static final Pattern TRAILING_PUNCT =
      Pattern.compile("[.,;:!?”'\"]+$");

  /** Id regex mirroring {@code parser.ts}'s {@code ID_REGEX}. */
  private static final Pattern ID_REGEX = Pattern.compile("^[A-Za-z0-9_.\\-]+$");

  private final Provenance provenance;
  private final TransportTag transport;

  public MarkdownUrlExtractor(Provenance provenance, TransportTag transport) {
    this.provenance = Objects.requireNonNull(provenance, "provenance");
    this.transport = Objects.requireNonNull(transport, "transport");
  }

  /** Production factory: CORE provenance, LLM_EMISSION transport. */
  public static MarkdownUrlExtractor llmChatEmission() {
    return new MarkdownUrlExtractor(Provenance.core("1.0"), TransportTag.LLM_EMISSION);
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public Provenance provenance() {
    return provenance;
  }

  @Override
  public Stream<Intent> extract(String raw) {
    return extractUrls(raw).stream()
        .map(this::parseUrl)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .map(addr -> new Intent(addr, transport));
  }

  // ===== Syntactic decoder =====

  /**
   * Extract every {@code justsearch://} URL from a free-text string. Mirrors
   * {@code parser.ts}'s {@code extractUrls}; strips trailing prose
   * punctuation. Returns matches in document order.
   */
  public List<String> extractUrls(String text) {
    if (text == null || text.isEmpty()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    Matcher m = URL_PATTERN.matcher(text);
    while (m.find()) {
      String url = m.group();
      String stripped = TRAILING_PUNCT.matcher(url).replaceAll("");
      if (!stripped.isEmpty()) {
        url = stripped;
      }
      out.add(url);
    }
    return out;
  }

  /**
   * Parse a single {@code justsearch://} URL into a {@link ShellAddress}.
   * Returns empty on malformed input; never throws. Mirrors {@code parser.ts}.
   *
   * <p>Cross-language conformance enforced by
   * {@code MarkdownUrlExtractorConformanceTest} against the shared
   * {@code scripts/ci/url-grammar-fixtures/v1.json} corpus.
   */
  public Optional<ShellAddress> parseUrl(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    URI parsed;
    try {
      parsed = new URI(raw);
    } catch (URISyntaxException e) {
      return Optional.empty();
    }
    if (!"justsearch".equals(parsed.getScheme())) {
      return Optional.empty();
    }
    String host = parsed.getHost();
    if (host == null) {
      // Java URI requires '//' for host; raw URLs without it (e.g.,
      // "justsearch:foo") parse with host=null.
      return Optional.empty();
    }
    boolean isSurface = "surface".equals(host);
    boolean isOp = "op".equals(host);
    boolean isQuery = "query".equals(host);
    boolean isAnswer = "answer".equals(host);
    if (!isSurface && !isOp && !isQuery && !isAnswer) {
      return Optional.empty();
    }
    if (isAnswer) {
      // 548 §4.5: justsearch://answer?q=<prompt>[&shape=<id>] → Answer. Free-form prompt in `q`,
      // optional `shape` (default core.rag-ask); path must be empty.
      String answerPath = parsed.getRawPath();
      String normalizedAnswerPath =
          answerPath == null ? "" : answerPath.replaceAll("^/+", "").replaceAll("/+$", "");
      if (!normalizedAnswerPath.isEmpty()) {
        return Optional.empty();
      }
      Map<String, Object> answerBag = parseQueryAsBag(parsed.getRawQuery());
      Object q = answerBag.get("q");
      if (!(q instanceof String prompt) || prompt.isBlank()) {
        return Optional.empty();
      }
      Object shapeRaw = answerBag.get("shape");
      String shape = shapeRaw instanceof String s && !s.isBlank() ? s : ShellAddress.Answer.DEFAULT_SHAPE;
      Map<String, Object> stateBag = new LinkedHashMap<>(answerBag);
      stateBag.remove("q");
      stateBag.remove("shape");
      return Optional.of(new ShellAddress.Answer(prompt, shape, new StateSnapshot(stateBag)));
    }
    if (isQuery) {
      // 548 S4-A: justsearch://query?q=<text>[&k=v] → Query. The query text is free-form
      // (not a catalog id), so it lives in the `q` param and the path must be empty.
      String queryPath = parsed.getRawPath();
      String normalizedQueryPath =
          queryPath == null ? "" : queryPath.replaceAll("^/+", "").replaceAll("/+$", "");
      if (!normalizedQueryPath.isEmpty()) {
        return Optional.empty();
      }
      Map<String, Object> queryBag = parseQueryAsBag(parsed.getRawQuery());
      Object q = queryBag.get("q");
      if (!(q instanceof String text) || text.isBlank()) {
        return Optional.empty();
      }
      // Refinement state = everything except `q`. Copy first — parseQueryAsBag may return
      // an immutable Map.of() for an empty query string.
      Map<String, Object> stateBag = new LinkedHashMap<>(queryBag);
      stateBag.remove("q");
      return Optional.of(new ShellAddress.Query(text, new StateSnapshot(stateBag)));
    }
    String rawPath = parsed.getRawPath();
    if (rawPath == null) {
      return Optional.empty();
    }
    // Strip leading + trailing slashes; require single segment.
    String path = rawPath.replaceAll("^/+", "").replaceAll("/+$", "");
    if (path.isEmpty() || path.contains("/")) {
      return Optional.empty();
    }
    if (!ID_REGEX.matcher(path).matches()) {
      return Optional.empty();
    }
    Map<String, Object> bag = parseQueryAsBag(parsed.getRawQuery());
    try {
      if (isSurface) {
        return Optional.of(
            new ShellAddress.Navigation(new SurfaceRef(path), new StateSnapshot(bag)));
      }
      // Invocation — serialize args bag as JSON-shaped string. Empty bag -> "{}".
      String argsJson = bagToArgsJson(bag);
      return Optional.of(
          new ShellAddress.Invocation(new OperationRef(path), argsJson, Optional.empty()));
    } catch (IllegalArgumentException refConstructionFailed) {
      // The platform's NamespacedId regex on {SurfaceRef, OperationRef} is stricter
      // than parser.ts's permissive id regex (e.g., uppercase letters fail
      // NamespacedId but pass parser.ts's ID_REGEX). Treat construction failure as
      // parse failure: the parser's job is to produce a *valid* ShellAddress, and
      // an id that can't construct a ref is by definition not a valid address.
      return Optional.empty();
    }
  }

  // ===== Query parsing =====

  private static Map<String, Object> parseQueryAsBag(String rawQuery) {
    if (rawQuery == null || rawQuery.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (String pair : rawQuery.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int eq = pair.indexOf('=');
      String key = eq >= 0 ? pair.substring(0, eq) : pair;
      String value = eq >= 0 ? pair.substring(eq + 1) : "";
      String decodedKey = decode(key);
      String decodedValue = decode(value);
      Object existing = out.get(decodedKey);
      if (existing == null) {
        out.put(decodedKey, decodedValue);
      } else if (existing instanceof List<?> list) {
        @SuppressWarnings("unchecked")
        List<Object> mutable = (List<Object>) list;
        mutable.add(decodedValue);
      } else {
        List<Object> arr = new ArrayList<>();
        arr.add(existing);
        arr.add(decodedValue);
        out.put(decodedKey, arr);
      }
    }
    return out;
  }

  private static String decode(String s) {
    return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
  }

  private static String bagToArgsJson(Map<String, Object> bag) {
    if (bag.isEmpty()) {
      return "{}";
    }
    // writeValueAsString throws JacksonException on internal serialization errors.
    // The bag here is built exclusively from String key + String/List<String> values
    // (parseQueryAsBag's only output shape), so serialization never legitimately
    // fails in practice; the catch is a defensive guard around the type-erasure
    // boundary. Logged on failure so a future invariant break is observable rather
    // than silently producing "{}".
    try {
      return MAPPER.writeValueAsString(bag);
    } catch (tools.jackson.core.JacksonException e) {
      org.slf4j.LoggerFactory.getLogger(MarkdownUrlExtractor.class)
          .warn("MarkdownUrlExtractor: failed to serialize args bag (returning empty): {}", e.getMessage());
      return "{}";
    }
  }

  private static final tools.jackson.databind.ObjectMapper MAPPER =
      new tools.jackson.databind.ObjectMapper();
}
