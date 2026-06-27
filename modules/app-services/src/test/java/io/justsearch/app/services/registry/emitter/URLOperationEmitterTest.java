package io.justsearch.app.services.registry.emitter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.ShellAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link URLOperationEmitter} per tempdoc 487 §4.2 / §6 step 11.
 *
 * <p>Verifies canonical-form output (alphabetized keys, repeated-key arrays,
 * percent-encoded values) and equivalence with the slice-487 §5 grammar corpus
 * the parser side of the conformance test consumes.
 */
final class URLOperationEmitterTest {

  private final URLOperationEmitter emitter = new URLOperationEmitter();

  @Test
  void bareUrlHasNoQueryString() {
    String url = emitter.toUrl(new OperationRef("core.ping-backend"), Map.of());
    assertEquals("justsearch://op/core.ping-backend", url);
  }

  @Test
  void scalarArgIsEncoded() {
    String url = emitter.toUrl(new OperationRef("core.search-index"), Map.of("query", "foo"));
    assertEquals("justsearch://op/core.search-index?query=foo", url);
  }

  @Test
  void multipleArgsAlphabetized() {
    String url =
        emitter.toUrl(
            new OperationRef("core.search-index"),
            new java.util.LinkedHashMap<>(
                Map.of("query", "foo", "limit", "10", "folder", "docs")));
    assertEquals(
        "justsearch://op/core.search-index?folder=docs&limit=10&query=foo", url);
  }

  @Test
  void arrayValuesRepeatKey() {
    String url =
        emitter.toUrl(
            new OperationRef("core.bulk-reindex"),
            Map.of("corpusIds", List.of("a", "b", "c")));
    assertEquals(
        "justsearch://op/core.bulk-reindex?corpusIds=a&corpusIds=b&corpusIds=c", url);
  }

  @Test
  void percentEncodedValues() {
    String url =
        emitter.toUrl(
            new OperationRef("core.add-watched-root"), Map.of("path", "/home/alex/docs"));
    assertEquals(
        "justsearch://op/core.add-watched-root?path=%2Fhome%2Falex%2Fdocs", url);
  }

  @Test
  void nullValuesAreSkipped() {
    var args = new java.util.HashMap<String, Object>();
    args.put("a", "value");
    args.put("b", null);
    args.put("c", "another");
    String url = emitter.toUrl(new OperationRef("core.ping-backend"), args);
    assertEquals("justsearch://op/core.ping-backend?a=value&c=another", url);
  }

  @Test
  void invocationOverloadDiscardsConfirmationToken() {
    ShellAddress.Invocation address =
        new ShellAddress.Invocation(
            new OperationRef("core.ping-backend"),
            "{}",
            Optional.of("agent-loop-handleSafetyGate-approved"));
    String url = emitter.toUrl(address);
    // Confirmation token MUST NOT appear in the URL — it's an invocation-side
    // metadata field, not part of the URL surface (per tempdoc Appendix A.5).
    assertEquals("justsearch://op/core.ping-backend", url);
  }

  @Test
  void invocationOverloadParsesArgsJson() {
    ShellAddress.Invocation address =
        ShellAddress.Invocation.of(
            new OperationRef("core.search-index"), "{\"query\":\"foo\",\"limit\":10}");
    String url = emitter.toUrl(address);
    assertEquals("justsearch://op/core.search-index?limit=10&query=foo", url);
  }

  @Test
  void invocationOverloadHandlesMalformedJsonGracefully() {
    ShellAddress.Invocation address =
        ShellAddress.Invocation.of(new OperationRef("core.ping-backend"), "not json");
    String url = emitter.toUrl(address);
    // Defensive: empty args fall back to bare URL rather than throwing.
    assertEquals("justsearch://op/core.ping-backend", url);
  }
}
