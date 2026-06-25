package io.justsearch.app.services.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.ShellAddress;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Slice 487 §5 — Java half of the cross-language URL grammar conformance test.
 *
 * <p>Consumes the shared corpus at
 * {@code scripts/ci/url-grammar-fixtures/v1.json} and asserts that
 * {@link MarkdownUrlExtractor#extractUrls} + {@link MarkdownUrlExtractor#parseUrl}
 * produce the expected output for every fixture. The TS counterpart at
 * {@code modules/ui-web/src/shell-v0/router/parser.conformance.test.ts} consumes
 * the same corpus; drift between the two implementations fails one or both tests.
 *
 * <p>The anti-drift mechanism per tempdoc §3.7 / §5.
 */
final class MarkdownUrlExtractorConformanceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final MarkdownUrlExtractor PARSER =
      MarkdownUrlExtractor.llmChatEmission();

  @TestFactory
  List<DynamicTest> corpusConformance() throws IOException {
    JsonNode root = loadCorpus();
    List<DynamicTest> tests = new java.util.ArrayList<>();
    JsonNode fixtures = root.get("fixtures");
    assertTrue(fixtures != null && fixtures.isArray(), "v1.json missing fixtures array");
    for (JsonNode fixture : fixtures) {
      String id = fixture.get("id").asString();
      // Slice 487 §5: skip fixtures explicitly excluded from cross-port conformance
      // (typically: known divergences pending platform-wide resolution).
      if (fixture.has("_excluded") && fixture.get("_excluded").asBoolean()) {
        continue;
      }
      String input = fixture.get("input").asString();
      if (fixture.has("extract")) {
        JsonNode expected = fixture.get("extract");
        tests.add(
            DynamicTest.dynamicTest(
                "extract: " + id, () -> assertExtractMatches(input, expected)));
      } else {
        JsonNode expected = fixture.get("expected");
        tests.add(
            DynamicTest.dynamicTest(
                "parse: " + id, () -> assertParseMatches(input, expected)));
      }
    }
    return tests;
  }

  private void assertExtractMatches(String input, JsonNode expected) {
    List<String> actual = PARSER.extractUrls(input);
    assertEquals(
        expected.size(),
        actual.size(),
        () -> "extract count mismatch for input: " + input + " — got " + actual);
    for (int i = 0; i < expected.size(); i++) {
      String expectedUrl = expected.get(i).get("url").asString();
      assertEquals(expectedUrl, actual.get(i), "extract[" + i + "] URL mismatch");
    }
  }

  @SuppressWarnings("unchecked")
  private void assertParseMatches(String input, JsonNode expected) {
    Optional<ShellAddress> actual = PARSER.parseUrl(input);
    if (expected == null || expected.isNull()) {
      assertTrue(actual.isEmpty(), "expected null parse for: " + input + " — got " + actual);
      return;
    }
    assertTrue(actual.isPresent(), "expected parse for: " + input);
    ShellAddress addr = actual.get();
    String expectedKind = expected.get("kind").asString();
    if ("query".equals(expectedKind)) {
      // 548 S4-A: query has no `target` — free text in `query`, refinements in `state`.
      var query = assertInstanceOf(ShellAddress.Query.class, addr);
      assertEquals(expected.get("query").asString(), query.query());
      Map<String, Object> expectedState =
          (Map<String, Object>) MAPPER.convertValue(expected.get("state"), Map.class);
      assertEquals(expectedState, query.state().values(), "Query state mismatch");
      return;
    }
    if ("answer".equals(expectedKind)) {
      // 548 §4.5: answer has no `target` — free-form prompt + shape + refinement state.
      var answer = assertInstanceOf(ShellAddress.Answer.class, addr);
      assertEquals(expected.get("prompt").asString(), answer.prompt());
      assertEquals(expected.get("shape").asString(), answer.shape());
      Map<String, Object> expectedState =
          (Map<String, Object>) MAPPER.convertValue(expected.get("state"), Map.class);
      assertEquals(expectedState, answer.state().values(), "Answer state mismatch");
      return;
    }
    String expectedTarget = expected.get("target").asString();
    if ("navigate".equals(expectedKind)) {
      var nav = assertInstanceOf(ShellAddress.Navigation.class, addr);
      assertEquals(expectedTarget, nav.target().value());
      Map<String, Object> expectedState =
          (Map<String, Object>) MAPPER.convertValue(expected.get("state"), Map.class);
      assertEquals(expectedState, nav.state().values(), "Navigation state mismatch");
    } else if ("invoke".equals(expectedKind)) {
      var inv = assertInstanceOf(ShellAddress.Invocation.class, addr);
      assertEquals(expectedTarget, inv.target().value());
      Map<String, Object> expectedArgs =
          (Map<String, Object>) MAPPER.convertValue(expected.get("args"), Map.class);
      Map<String, Object> actualArgs =
          inv.argsJson().equals("{}")
              ? Map.of()
              : (Map<String, Object>)
                  MAPPER.readValue(inv.argsJson(), Map.class);
      assertEquals(expectedArgs, actualArgs, "Invocation args mismatch");
    } else {
      throw new AssertionError("Unknown fixture kind: " + expectedKind);
    }
  }

  private static JsonNode loadCorpus() throws IOException {
    // Worktree root is the cwd for gradle test execution; fixtures path is relative.
    Path corpus =
        Paths.get(System.getProperty("user.dir"))
            .resolve("../..")
            .resolve("scripts/ci/url-grammar-fixtures/v1.json")
            .normalize();
    if (!Files.exists(corpus)) {
      // Fallback: try repo-root-relative (when test runs from repo root).
      corpus = Paths.get("scripts/ci/url-grammar-fixtures/v1.json");
    }
    assertTrue(Files.exists(corpus), "URL grammar fixture corpus not found at: " + corpus);
    return MAPPER.readTree(Files.readString(corpus));
  }
}
