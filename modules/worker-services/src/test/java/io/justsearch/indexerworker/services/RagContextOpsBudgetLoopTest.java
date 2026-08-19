/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.rag.ContextBudgeter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 849 §2.3 — the budget loop must count a hit as "used" only when it actually contributed
 * text.
 *
 * <p>{@code STOPPED_BUDGET} means NOTHING was appended; {@code APPENDED_TRUNCATED} means something
 * was. Both used to map onto one {@code AppendOutcome.APPENDED_AND_STOPPED}, and both consumers
 * then added the hit to {@code used}. Because the loop breaks straight after, the extra entry is
 * always the TAIL — so the damage is not wholesale misalignment. It is a fabricated inclusion claim
 * (the final passage of a budget-exhausted retrieval gets a citation asserting it reached the model
 * while contributing zero characters) plus a {@code chunksIncluded} inflated by one, since that
 * count is {@code used.size()}.
 *
 * <p>The invariant asserted here is the one {@link ContextBudgeter#sectionHeader} is written
 * against: the used-hit list and {@code sections()} are appended in ONE iteration, so section
 * <i>i</i> ⇔ citation <i>i</i> ⇔ the FE's {@code sources[i]}. Reverting either
 * {@code mapAppendResult} overload fails {@code budgetExhaustedHitIsNotCountedAsUsed}.
 */
@DisplayName("RagContextOps — budget loop honesty (tempdoc 849)")
final class RagContextOpsBudgetLoopTest {

  private static final int MAX_CHUNKS_PER_ARTICLE = 10;

  private static RagContextOps ops() {
    ResolvedConfig cfg = new ResolvedConfigBuilder().build();
    return new RagContextOps(null, null, null, () -> cfg, null);
  }

  private static LuceneRuntimeTypes.SearchHit chunkHit(String parentDocId, String content) {
    return new LuceneRuntimeTypes.SearchHit(
        parentDocId + "#0",
        1.0f,
        Map.of(
            SchemaFields.CHUNK_CONTENT, content,
            SchemaFields.PARENT_DOC_ID, parentDocId));
  }

  /**
   * The exact character budget that fits hit A's rendered section (header + content) with a few
   * characters to spare — measured by running the loop rather than re-deriving the label format,
   * so the fixture cannot drift from {@code buildContextLabel}.
   */
  private static int budgetThatFitsExactlyOneSection(LuceneRuntimeTypes.SearchHit only) {
    ContextBudgeter probe = new ContextBudgeter(1_000_000);
    ops().runBudgetLoop(
        List.of(only),
        new ArrayList<>(),
        Map.of(),
        MAX_CHUNKS_PER_ARTICLE,
        (label, content) -> RagContextOps.mapAppendResult(probe.appendSection(label, content)));
    // +3 leaves a remainder far smaller than the next section's separator + header, so the second
    // append returns STOPPED_BUDGET (nothing written) rather than APPENDED_TRUNCATED.
    return probe.length() + 3;
  }

  @Test
  @DisplayName("849: a hit the budget could not append at all is NOT counted as used")
  void budgetExhaustedHitIsNotCountedAsUsed() {
    var first = chunkHit("doc-a", "the first passage, which fits the budget exactly");
    var second = chunkHit("doc-b", "the second passage, which cannot be appended at all");
    int budget = budgetThatFitsExactlyOneSection(first);

    ContextBudgeter budgeter = new ContextBudgeter(budget);
    List<LuceneRuntimeTypes.SearchHit> used = new ArrayList<>();
    boolean truncated =
        ops().runBudgetLoop(
            List.of(first, second),
            used,
            Map.of(),
            MAX_CHUNKS_PER_ARTICLE,
            (label, content) ->
                RagContextOps.mapAppendResult(budgeter.appendSection(label, content)));

    assertTrue(truncated, "the budget was exhausted, so the turn must report itself truncated");
    assertEquals(
        1,
        budgeter.sections().size(),
        "precondition: only the first section was actually written to the context");
    assertEquals(
        1,
        used.size(),
        "the second hit contributed ZERO characters; counting it fabricates an inclusion claim"
            + " and inflates chunksIncluded (= used.size())");
    assertEquals(
        budgeter.sections().size(),
        used.size(),
        "section i must stay the citation at position i — the contract sources[n-1] resolves");
    assertEquals("doc-a", used.get(0).fields().get(SchemaFields.PARENT_DOC_ID));
  }

  @Test
  @DisplayName("849: a hit appended WITH its tail cut is still counted — the two outcomes differ")
  void tailCutHitIsStillCountedAsUsed() {
    var first = chunkHit("doc-a", "the first passage, which fits the budget exactly");
    var second = chunkHit("doc-b", "x".repeat(400));
    // Room for the whole first section plus the second section's overhead and a slice of its
    // content: the second append writes SOMETHING, so it must be counted.
    int budget = budgetThatFitsExactlyOneSection(first) + 200;

    ContextBudgeter budgeter = new ContextBudgeter(budget);
    List<LuceneRuntimeTypes.SearchHit> used = new ArrayList<>();
    boolean truncated =
        ops().runBudgetLoop(
            List.of(first, second),
            used,
            Map.of(),
            MAX_CHUNKS_PER_ARTICLE,
            (label, content) ->
                RagContextOps.mapAppendResult(budgeter.appendSection(label, content)));

    assertTrue(truncated);
    assertEquals(
        2,
        budgeter.sections().size(),
        "precondition: the second section WAS written, with its tail cut");
    assertEquals(2, used.size(), "a hit that contributed text stays counted — this is not a blanket"
        + " 'stop means drop' rule, it distinguishes the two stop reasons");
    assertEquals(budgeter.sections().size(), used.size());
  }

  @Test
  @DisplayName("849: everything fitting leaves the loop untruncated and every hit counted")
  void everythingFitsIsUnchanged() {
    var first = chunkHit("doc-a", "short one");
    var second = chunkHit("doc-b", "short two");

    ContextBudgeter budgeter = new ContextBudgeter(1_000_000);
    List<LuceneRuntimeTypes.SearchHit> used = new ArrayList<>();
    boolean truncated =
        ops().runBudgetLoop(
            List.of(first, second),
            used,
            Map.of(),
            MAX_CHUNKS_PER_ARTICLE,
            (label, content) ->
                RagContextOps.mapAppendResult(budgeter.appendSection(label, content)));

    assertEquals(false, truncated, "nothing was cut, so nothing may report truncation");
    assertEquals(2, used.size());
    assertEquals(2, budgeter.sections().size());
  }

  @Test
  @DisplayName("849: the overflow backfill applies the same distinction as the primary pass")
  void overflowBackfillDoesNotCountANonAppendedHit() {
    // maxChunksPerArticle = 1 pushes the second hit of the SAME parent into the overflow list, so
    // the backfill loop (not the primary pass) is what runs out of budget.
    var first = chunkHit("doc-a", "the first passage, which fits the budget exactly");
    var overflowed = chunkHit("doc-a", "the overflow passage, which cannot be appended at all");
    int budget = budgetThatFitsExactlyOneSection(first) + 3;

    ContextBudgeter budgeter = new ContextBudgeter(budget);
    List<LuceneRuntimeTypes.SearchHit> used = new ArrayList<>();
    boolean truncated =
        ops().runBudgetLoop(
            List.of(first, overflowed),
            used,
            Map.of(),
            1,
            (label, content) ->
                RagContextOps.mapAppendResult(budgeter.appendSection(label, content)));

    assertTrue(truncated, "the backfill exhausted the budget");
    assertEquals(1, budgeter.sections().size(), "precondition: the backfill wrote nothing");
    assertEquals(
        1, used.size(), "the backfill must not count a hit it could not append either");
  }
}
