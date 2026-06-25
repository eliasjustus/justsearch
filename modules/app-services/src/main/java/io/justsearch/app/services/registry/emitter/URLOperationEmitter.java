/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.emitter;

import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.ShellAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Projects an {@link Operation} reference + args into a canonical
 * {@code justsearch://op/...} URL string.
 *
 * <p>Per tempdoc 487 §4.2 / §6 step 11: the outbound catalog &rarr; URL
 * projection for Operation invocations. Sibling of the already-shipped
 * {@link URLSurfaceEmitter} (which handles Surface activations / the
 * {@link ShellAddress.Navigation} half of the address taxonomy); this emitter
 * handles the {@link ShellAddress.Invocation} half.
 *
 * <p>Grammar (mirrors slice 489 / parser.ts):
 *
 * <pre>
 *   justsearch://op/&lt;operationId&gt;[?argName=value&amp;argName=value...]
 * </pre>
 *
 * <p>Canonicalization: keys sorted alphabetically; values URL-encoded; array
 * values produce repeated keys ({@code ?ids=a&ids=b} not {@code ?ids=a,b}) —
 * matches {@link URLSurfaceEmitter} and the
 * {@code modules/ui-web/src/shell-v0/router/parser.ts} convention.
 *
 * <p>Stateless. Pure function. Safe to call from any thread.
 *
 * <p><strong>Forward-compat: no production consumer in V1.</strong> Reserved
 * for slice 487 §A.6 chat-receipt UI / copy-URL / bookmark-export consumer
 * slices. The slice 487 V1 prompt-descriptor block is rendered by
 * {@code URLEmissionGrammar} which walks the catalog directly (see
 * comment at that file's {@code renderOpDescriptor}); inserting URL
 * templates from this emitter risks regressing the load-bearing G2 probe
 * baseline and is deliberately deferred. Matches the slice 489 §6
 * convention of shipping URL emitters ahead of their consumers
 * ({@code URLSurfaceEmitter} followed the same pattern). Cross-language
 * URL conformance is governed by
 * {@code scripts/ci/url-grammar-fixtures/v1.json} (see tempdoc §5).
 */
public final class URLOperationEmitter {

  public static final String SCHEME = "justsearch";
  public static final String HOST = "op";

  /**
   * Project an Operation invocation address (ref + args) into a canonical URL string.
   *
   * @param target the {@link OperationRef} naming the operation
   * @param args the args map to encode as query parameters; empty produces a
   *     bare {@code justsearch://op/&lt;id&gt;} with no query string
   * @return the canonical URL string
   * @throws NullPointerException if either argument is null
   */
  public String toUrl(OperationRef target, Map<String, Object> args) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(args, "args");
    StringBuilder sb = new StringBuilder();
    sb.append(SCHEME).append("://").append(HOST).append('/').append(target.value());
    String query = encodeQuery(args);
    if (!query.isEmpty()) {
      sb.append('?').append(query);
    }
    return sb.toString();
  }

  /**
   * Project a {@link ShellAddress.Invocation} address into a canonical URL string.
   *
   * <p>Discards {@link ShellAddress.Invocation#confirmationToken()} — the token is
   * an invocation-side metadata field, not part of the URL surface (per the W3C
   * capability-URL posture deferred in tempdoc Appendix A.5 and the §4.4 lattice
   * which carries trust orthogonally). The emitted URL is the public address
   * shape; tokens flow through {@code InvocationProvenance} / the dispatcher's
   * 4-arg overload, not the URL.
   *
   * <p>{@code argsJson} is parsed as a JSON object; if parsing fails the URL is
   * emitted bare (no query string) — the LLM prompt-renderer use-case only
   * ever supplies empty / well-formed args so this branch is defensive.
   */
  public String toUrl(ShellAddress.Invocation address) {
    Objects.requireNonNull(address, "address");
    Map<String, Object> args = parseArgsJsonOrEmpty(address.argsJson());
    return toUrl(address.target(), args);
  }

  /**
   * Project an Operation manifest into a URL template (the bare operation URL
   * with no arguments).
   *
   * <p>Useful for catalog-wide URL emission (e.g., a future chat-receipt
   * UI's "Copy URL" affordance, bookmark export, or a per-op manifest
   * descriptor block — the per-Op URL template is the bare form). Not
   * called by V1 production code; see class-level Javadoc for the
   * forward-compat scope.
   */
  public String toUrlTemplate(Operation op) {
    Objects.requireNonNull(op, "op");
    return toUrl(op.id(), Map.of());
  }

  // ----- internal helpers -----

  private static String encodeQuery(Map<String, Object> values) {
    if (values.isEmpty()) {
      return "";
    }
    Map<String, Object> sorted = new TreeMap<>(Comparator.naturalOrder());
    sorted.putAll(values);
    List<String> parts = new ArrayList<>();
    for (Map.Entry<String, Object> entry : sorted.entrySet()) {
      String encodedKey = URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8);
      Object value = entry.getValue();
      if (value == null) {
        continue;
      }
      if (value instanceof List<?> list) {
        for (Object item : list) {
          if (item != null) {
            parts.add(encodedKey + "=" + encode(item));
          }
        }
      } else if (value instanceof Object[] arr) {
        for (Object item : arr) {
          if (item != null) {
            parts.add(encodedKey + "=" + encode(item));
          }
        }
      } else {
        parts.add(encodedKey + "=" + encode(value));
      }
    }
    return String.join("&", parts);
  }

  private static String encode(Object value) {
    return URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8);
  }

  /**
   * Best-effort JSON-object parsing for the {@link ShellAddress.Invocation#argsJson()}
   * convenience overload. Defensive: returns an empty map on any parse failure
   * (the prompt-renderer use-case never supplies malformed JSON; this branch
   * exists for safety, not as a primary code path).
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseArgsJsonOrEmpty(String argsJson) {
    if (argsJson == null || argsJson.isBlank()) {
      return Map.of();
    }
    try {
      Object parsed = MAPPER.readValue(argsJson, Object.class);
      if (parsed instanceof Map<?, ?> map) {
        return (Map<String, Object>) map;
      }
      return Map.of();
    } catch (tools.jackson.core.JacksonException malformedJson) {
      // Defensive fallback for the convenience overload: callers feeding URL emitters
      // with arbitrary argsJson Strings can legitimately supply non-object payloads.
      // Production call site is the URLEmissionGrammar prompt-renderer which only
      // passes Map.of() — this branch never triggers in V1.
      return Map.of();
    }
  }

  private static final tools.jackson.databind.ObjectMapper MAPPER =
      new tools.jackson.databind.ObjectMapper();
}
