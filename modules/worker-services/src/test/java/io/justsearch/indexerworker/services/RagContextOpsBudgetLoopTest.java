/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.rag.ContextBudgeter;
import io.justsearch.indexing.rag.TokenAwareBudgeter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
 * <p><b>Both budgeters are exercised, and that is load-bearing</b> (review F1). Java resolves the
 * two {@code mapAppendResult} overloads STATICALLY, so a test that only ever constructs a
 * {@link ContextBudgeter} binds the {@link ContextBudgeter} overload at every call site and leaves
 * the {@link TokenAwareBudgeter} one — the PRODUCTION-DEFAULT path, selected whenever
 * {@code maxContextTokens > 0}, which is every open retrieval — with zero coverage. Reverting
 * either overload therefore has to fail a named test here, so each case runs against both.
 *
 * <p>The invariant asserted is the one {@link ContextBudgeter#sectionHeader} is written against: the
 * used-hit list and {@code sections()} are appended in ONE iteration, so section <i>i</i> ⇔ citation
 * <i>i</i> ⇔ the FE's {@code sources[i]}.
 */
@DisplayName("RagContextOps — budget loop honesty (tempdoc 849)")
final class RagContextOpsBudgetLoopTest {

  private static final int MAX_CHUNKS_PER_ARTICLE = 10;

  /**
   * The two budgeters the loop runs against. {@code TOKEN} is what production picks for an open
   * retrieval ({@code RagContextOps} selects {@link TokenAwareBudgeter} when
   * {@code maxContextTokens > 0}, and {@code RAGContext.tryOpenRetrieval} always sends a positive
   * budget); {@code CHAR} is the scoped path.
   */
  private enum Kind {
    CHAR,
    TOKEN
  }

  /**
   * Uniform view over the two budgeter types, so one case body can run against both. Each
   * {@code append} call sites the overload under test: the concrete budgeter's static type is what
   * selects the {@code mapAppendResult} overload.
   */
  private interface Budgeter {
    RagContextOps.AppendOutcome append(String label, String content);

    int sectionCount();

    /** Budget consumed so far, in the budgeter's own unit (chars / tokens). */
    int consumed();
  }

  private static Budgeter budgeter(Kind kind, int budget) {
    if (kind == Kind.CHAR) {
      ContextBudgeter b = new ContextBudgeter(budget);
      return new Budgeter() {
        @Override
        public RagContextOps.AppendOutcome append(String label, String content) {
          return RagContextOps.mapAppendResult(b.appendSection(label, content));
        }

        @Override
        public int sectionCount() {
          return b.sections().size();
        }

        @Override
        public int consumed() {
          return b.length();
        }
      };
    }
    TokenAwareBudgeter b = new TokenAwareBudgeter(budget);
    return new Budgeter() {
      @Override
      public RagContextOps.AppendOutcome append(String label, String content) {
        return RagContextOps.mapAppendResult(b.appendSection(label, content));
      }

      @Override
      public int sectionCount() {
        return b.sections().size();
      }

      @Override
      public int consumed() {
        return b.estimatedTokens();
      }
    };
  }

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
   * The budget that fits hit A's rendered section exactly — measured by running the loop with a
   * huge budget rather than re-deriving the label format, so the fixture cannot drift from
   * {@code buildContextLabel}.
   */
  private static int budgetThatFitsExactlyOneSection(Kind kind, LuceneRuntimeTypes.SearchHit only) {
    Budgeter probe = budgeter(kind, 1_000_000);
    ops().runBudgetLoop(
        List.of(only), new ArrayList<>(), Map.of(), MAX_CHUNKS_PER_ARTICLE, probe::append);
    return probe.consumed();
  }

  @Test
  @DisplayName("849: STOPPED_BUDGET maps to break-WITHOUT-add on BOTH overloads")
  void bothOverloadsDistinguishStoppedFromTailCut() {
    // Pins the overload selection itself: the two `case` arms below are separate source lines in
    // separate methods, so reverting either one alone is caught here even before the loop cases.
    assertEquals(
        RagContextOps.AppendOutcome.STOPPED_WITHOUT_APPEND,
        RagContextOps.mapAppendResult(ContextBudgeter.AppendResult.STOPPED_BUDGET));
    assertEquals(
        RagContextOps.AppendOutcome.STOPPED_WITHOUT_APPEND,
        RagContextOps.mapAppendResult(TokenAwareBudgeter.AppendResult.STOPPED_BUDGET));
    assertEquals(
        RagContextOps.AppendOutcome.APPENDED_AND_STOPPED,
        RagContextOps.mapAppendResult(ContextBudgeter.AppendResult.APPENDED_TRUNCATED));
    assertEquals(
        RagContextOps.AppendOutcome.APPENDED_AND_STOPPED,
        RagContextOps.mapAppendResult(TokenAwareBudgeter.AppendResult.APPENDED_TRUNCATED));
    assertEquals(
        RagContextOps.AppendOutcome.APPENDED,
        RagContextOps.mapAppendResult(ContextBudgeter.AppendResult.APPENDED));
    assertEquals(
        RagContextOps.AppendOutcome.APPENDED,
        RagContextOps.mapAppendResult(TokenAwareBudgeter.AppendResult.APPENDED));
    assertEquals(
        RagContextOps.AppendOutcome.SKIPPED,
        RagContextOps.mapAppendResult(ContextBudgeter.AppendResult.SKIPPED_EMPTY));
    assertEquals(
        RagContextOps.AppendOutcome.SKIPPED,
        RagContextOps.mapAppendResult(TokenAwareBudgeter.AppendResult.SKIPPED_EMPTY));
  }

  @ParameterizedTest
  @EnumSource(Kind.class)
  @DisplayName("849: a hit the budget could not append at all is NOT counted as used")
  void budgetExhaustedHitIsNotCountedAsUsed(Kind kind) {
    var first = chunkHit("doc-a", "the first passage, which fits the budget exactly");
    var second = chunkHit("doc-b", "the second passage, which cannot be appended at all");
    // +3 leaves a remainder smaller than the next section's separator + header in BOTH units, so
    // the second append returns STOPPED_BUDGET (nothing written), not APPENDED_TRUNCATED.
    int budget = budgetThatFitsExactlyOneSection(kind, first) + 3;

    Budgeter b = budgeter(kind, budget);
    List<LuceneRuntimeTypes.SearchHit> used = new ArrayList<>();
    boolean truncated =
        ops().runBudgetLoop(
            List.of(first, second), used, Map.of(), MAX_CHUNKS_PER_ARTICLE, b::append);

    assertTrue(truncated, "the budget was exhausted, so the turn must report itself truncated");
    assertEquals(1, b.sectionCount(), "precondition: only the first section was written");
    assertEquals(
        1,
        used.size(),
        "the second hit contributed ZERO characters; counting it fabricates an inclusion claim"
            + " and inflates chunksIncluded (= used.size())");
    assertEquals(
        b.sectionCount(),
        used.size(),
        "section i must stay the citation at position i — the contract sources[n-1] resolves");
    assertEquals("doc-a", used.get(0).fields().get(SchemaFields.PARENT_DOC_ID));
  }

  @ParameterizedTest
  @EnumSource(Kind.class)
  @DisplayName("849: a hit appended WITH its tail cut is still counted — the two outcomes differ")
  void tailCutHitIsStillCountedAsUsed(Kind kind) {
    var first = chunkHit("doc-a", "the first passage, which fits the budget exactly");
    var second = chunkHit("doc-b", "x".repeat(4000));
    // Room for the second section's overhead plus a slice of its content in both units, but not
    // for all 4000 characters of it — so the second append writes SOMETHING and must be counted.
    int budget = budgetThatFitsExactlyOneSection(kind, first) + 200;

    Budgeter b = budgeter(kind, budget);
    List<LuceneRuntimeTypes.SearchHit> used = new ArrayList<>();
    boolean truncated =
        ops().runBudgetLoop(
            List.of(first, second), used, Map.of(), MAX_CHUNKS_PER_ARTICLE, b::append);

    assertTrue(truncated);
    assertEquals(2, b.sectionCount(), "precondition: the second section WAS written, tail cut");
    assertEquals(
        2,
        used.size(),
        "a hit that contributed text stays counted — this is not a blanket 'stop means drop' rule,"
            + " it distinguishes the two stop reasons");
    assertEquals(b.sectionCount(), used.size());
  }

  @ParameterizedTest
  @EnumSource(Kind.class)
  @DisplayName("849: everything fitting leaves the loop untruncated and every hit counted")
  void everythingFitsIsUnchanged(Kind kind) {
    var first = chunkHit("doc-a", "short one");
    var second = chunkHit("doc-b", "short two");

    Budgeter b = budgeter(kind, 1_000_000);
    List<LuceneRuntimeTypes.SearchHit> used = new ArrayList<>();
    boolean truncated =
        ops().runBudgetLoop(
            List.of(first, second), used, Map.of(), MAX_CHUNKS_PER_ARTICLE, b::append);

    assertEquals(false, truncated, "nothing was cut, so nothing may report truncation");
    assertEquals(2, used.size());
    assertEquals(2, b.sectionCount());
  }

  @ParameterizedTest
  @EnumSource(Kind.class)
  @DisplayName("849: the overflow backfill applies the same distinction as the primary pass")
  void overflowBackfillDoesNotCountANonAppendedHit(Kind kind) {
    // maxChunksPerArticle = 1 pushes the second hit of the SAME parent into the overflow list, so
    // the backfill loop (not the primary pass) is what runs out of budget.
    var first = chunkHit("doc-a", "the first passage, which fits the budget exactly");
    var overflowed = chunkHit("doc-a", "the overflow passage, which cannot be appended at all");
    int budget = budgetThatFitsExactlyOneSection(kind, first) + 3;

    Budgeter b = budgeter(kind, budget);
    List<LuceneRuntimeTypes.SearchHit> used = new ArrayList<>();
    boolean truncated =
        ops().runBudgetLoop(List.of(first, overflowed), used, Map.of(), 1, b::append);

    assertTrue(truncated, "the backfill exhausted the budget");
    assertEquals(1, b.sectionCount(), "precondition: the backfill wrote nothing");
    assertEquals(1, used.size(), "the backfill must not count a hit it could not append either");
  }
}
