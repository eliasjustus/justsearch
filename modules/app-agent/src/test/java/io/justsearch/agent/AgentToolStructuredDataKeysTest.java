/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 877 §2.3 — the conformance half of the {@code structuredData} key unification.
 *
 * <p>Four keys cross a producer/consumer seam and were spelled independently on each side. The Java
 * halves now import a constant from {@link OperationResult}, which makes a rename a compile error
 * within Java. That is only half the seam: {@code searchResults} and {@code lineage} are read by
 * hand-written TypeScript, and {@code feedbackFeatures} is read from a different Gradle module, so
 * neither reader can import the constant. A rename that compiles would still blind them silently.
 *
 * <p>So each key gets the treatment {@code grounding} already had in 865
 * ({@code AgentSessionGroundingTest}'s "the key the FE reads" assertion): pin the constant's VALUE
 * to the literal, and assert the named consumer's SOURCE still contains that literal. Renaming a key
 * now requires editing this test, which is exactly the deliberate act that was missing.
 *
 * <p>The TypeScript files named below are hand-written, not generated — there is no schema gate over
 * {@code structuredData} ({@code AgentRunShape} declares it as a free-form object), which is why the
 * check has to read source text rather than compare a generated artifact.
 */
class AgentToolStructuredDataKeysTest {

  private static final String TOOL_SEARCH_CARD_TS =
      "modules/ui-web/src/shell-v0/components/chat/toolSearchCard.ts";
  private static final String TOOL_OUTPUT_LINEAGE_TS =
      "modules/ui-web/src/shell-v0/components/chat/toolOutputLineage.ts";
  private static final String DISPOSITION_WIRING_JAVA =
      "modules/app-services/src/main/java/io/justsearch/app/services/feedback/AgentDispositionWiring.java";
  private static final String AGENT_SESSION_JAVA =
      "modules/app-agent/src/main/java/io/justsearch/agent/AgentSession.java";

  @Test
  @DisplayName("searchResults: value pinned, and the hand-written FE card still reads that literal")
  void searchResultsKey() {
    assertEquals(
        "searchResults",
        OperationResult.SEARCH_RESULTS_KEY,
        "the key SearchTool emits and AgentSession + toolSearchCard.ts read");
    assertConsumerReads(TOOL_SEARCH_CARD_TS, OperationResult.SEARCH_RESULTS_KEY);
  }

  @Test
  @DisplayName("readResults: value pinned; distinct from searchResults by design (868 §B.3)")
  void readResultsKey() {
    assertEquals(
        "readResults",
        OperationResult.READ_RESULTS_KEY,
        "the key ReadDocumentTool emits and AgentSession reads");
    // The acquisition axis depends on the two producer keys being DIFFERENT strings: a read tool
    // emitting the search key would mint sources indistinguishable from search hits, which is the
    // 865 §7.6 violation the axis exists to prevent. Asserted rather than assumed.
    assertTrue(
        !OperationResult.READ_RESULTS_KEY.equals(OperationResult.SEARCH_RESULTS_KEY),
        "opened and retrieved must not share a producer key");
    // §2.3's promise is "a producer emits it AND a consumer reads it" — pinning the value alone
    // leaves this key the one of the four with no named reader, i.e. unfalsifiable if the consumer
    // stopped reading it. AgentSession#contributeGroundingSources is that reader, and being Java it
    // gets the same treatment feedbackFeatures gets: it must use the CONSTANT, with no raw literal
    // beside it.
    assertJavaConsumerUsesConstant(
        AGENT_SESSION_JAVA, "READ_RESULTS_KEY", OperationResult.READ_RESULTS_KEY);
  }

  @Test
  @DisplayName("feedbackFeatures: value pinned, and the app-services consumer uses the CONSTANT")
  void feedbackFeaturesKey() {
    assertEquals(
        "feedbackFeatures",
        OperationResult.FEEDBACK_FEATURES_KEY,
        "the key SearchTool emits and AgentDispositionWiring reads");
    // A Java consumer CAN import the constant, so the literal-presence check the wire consumers get
    // would be exactly backwards here: adopting the constant REMOVES the literal. What this asserts
    // instead is that the literal has not crept back in beside it.
    assertJavaConsumerUsesConstant(
        DISPOSITION_WIRING_JAVA, "FEEDBACK_FEATURES_KEY", OperationResult.FEEDBACK_FEATURES_KEY);
  }

  @Test
  @DisplayName("lineage: value pinned, and the hand-written FE framing still reads that literal")
  void lineageKey() {
    assertEquals(
        "lineage",
        OperationResult.LINEAGE_KEY,
        "the key OperationResult.withLineage stamps and toolOutputLineage.ts reads");
    assertConsumerReads(TOOL_OUTPUT_LINEAGE_TS, OperationResult.LINEAGE_KEY);
  }

  @Test
  @DisplayName("withLineage stamps under the constant, not a private literal")
  void withLineageStampsUnderTheConstant() {
    OperationResult stamped =
        OperationResult.success("ok").withLineage(
            io.justsearch.agent.api.registry.OutputLineage.CORPUS_QUOTED);
    assertEquals(
        io.justsearch.agent.api.registry.OutputLineage.CORPUS_QUOTED.wireToken(),
        stamped.structuredData().get(OperationResult.LINEAGE_KEY),
        "the stamp and the reader name one constant");
  }

  @Test
  @DisplayName("grounding keeps its 865 constant alongside the four added here")
  void groundingKeyUnchanged() {
    assertEquals("grounding", OperationResult.GROUNDING_KEY, "the key the FE reads (865 §7.1)");
  }

  /**
   * A Java consumer in another module CAN import the constant, so the fact worth pinning is that it
   * does — and that the raw literal has not reappeared beside it. The compiler already guarantees
   * a rename propagates; what it cannot see is someone quietly re-typing the string.
   */
  private static void assertJavaConsumerUsesConstant(
      String relativePath, String constantName, String key) {
    String source = readConsumer(relativePath);
    assertTrue(
        source.contains(constantName),
        relativePath
            + " no longer references "
            + constantName
            + ". A Java consumer of this key must import the constant, not re-spell the literal.");
    assertFalse(
        source.contains("\"" + key + "\""),
        relativePath
            + " contains the raw literal \""
            + key
            + "\" as well as the constant. One of the two is now unnecessary, and a second speller"
            + " is how this key drifted in the first place.");
  }

  /**
   * Assert a consumer that CANNOT import the constant still spells the same literal. Skips (rather
   * than passes) when the source tree is not reachable from the test's working directory, so a
   * packaging change turns into a visible skip instead of a silent green.
   */
  private static void assertConsumerReads(String relativePath, String key) {
    String source = readConsumer(relativePath);
    assertTrue(
        source.contains("'" + key + "'") || source.contains("\"" + key + "\""),
        relativePath
            + " no longer contains the literal \""
            + key
            + "\". Either it stopped reading the key (then this consumer is blind and the"
            + " producer should stop emitting it), or the key was renamed on the Java side only"
            + " (then this consumer must be updated in the same change).");
  }

  /** Read a consumer's source, skipping (not passing) when the tree is not reachable. */
  private static String readConsumer(String relativePath) {
    Path file = repoRoot().resolve(relativePath);
    Assumptions.assumeTrue(
        Files.isRegularFile(file),
        "consumer source not reachable from the test working directory: " + relativePath);
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new AssertionError("could not read consumer source " + relativePath, e);
    }
  }

  /** Walk up to the repo root (the dir holding {@code governance/} + {@code settings.gradle.kts}). */
  private static Path repoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8 && dir != null; i++) {
      if (Files.isDirectory(dir.resolve("governance"))
          && Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    return Path.of("").toAbsolutePath();
  }
}
