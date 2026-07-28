/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 789 Phase 2 — unit coverage for the three delivery framings as pure functions: the
 * entity-not-in-query rule (F1), header composition (F2), and the zero/thin trigger (F3).
 *
 * <p>Companion to {@link McpFramingRenderSnapshotTest}, which pins the rendered before/after
 * deliveries end-to-end through the real renderers.
 */
@DisplayName("McpDeliveryFraming: F1/F2/F3 framing logic (tempdoc 789 Phase 2)")
final class McpDeliveryFramingTest {

  private static Map<String, Map<String, Long>> facets(Map<String, Long> persons) {
    Map<String, Map<String, Long>> out = new LinkedHashMap<>();
    out.put("entity_persons_raw", persons);
    return out;
  }

  @Nested
  @DisplayName("F1 — continuation")
  final class Continuation {

    @Test
    @DisplayName("names an entity the excerpt contains and the query does not")
    void firesOnEntityAbsentFromQuery() {
      Map<String, Long> vocab =
          McpDeliveryFraming.entityVocabulary(facets(Map.of("Vince Kaminski", 12L)));
      String line =
          McpDeliveryFraming.continuationLine(
              "Please route the risk memo through Vince Kaminski before Friday.",
              "who owns the risk memo",
              vocab);
      assertNotNull(line);
      assertTrue(line.contains("\"Vince Kaminski\""), line);
      assertTrue(line.contains("12 of the documents matching this search"), line);
      assertTrue(line.contains("follow-up search"), line);
    }

    @Test
    @DisplayName("suppressed when the query already names the entity — it is not an intermediate fact")
    void suppressedWhenQueryNamesEntity() {
      Map<String, Long> vocab =
          McpDeliveryFraming.entityVocabulary(facets(Map.of("Vince Kaminski", 12L)));
      assertNull(
          McpDeliveryFraming.continuationLine(
              "Please route the risk memo through Vince Kaminski before Friday.",
              "who did Vince Kaminski email about the risk memo",
              vocab));
    }

    @Test
    @DisplayName("a PARTIAL query overlap still fires — the agent has not been shown the full name")
    void partialQueryOverlapStillFires() {
      Map<String, Long> vocab =
          McpDeliveryFraming.entityVocabulary(facets(Map.of("Vince Kaminski", 4L)));
      assertNotNull(
          McpDeliveryFraming.continuationLine(
              "escalated to Vince Kaminski", "what did Kaminski decide", vocab));
    }

    @Test
    @DisplayName("suppressed when the delivered excerpt does not contain the entity at all")
    void suppressedWhenExcerptLacksEntity() {
      Map<String, Long> vocab =
          McpDeliveryFraming.entityVocabulary(facets(Map.of("Vince Kaminski", 12L)));
      assertNull(
          McpDeliveryFraming.continuationLine("a memo about quarterly hedging", "hedging", vocab));
    }

    @Test
    @DisplayName("empty vocabulary (no entity facets — NER incomplete or facets off) emits nothing")
    void emptyVocabularyEmitsNothing() {
      assertTrue(McpDeliveryFraming.entityVocabulary(null).isEmpty());
      assertTrue(McpDeliveryFraming.entityVocabulary(Map.of()).isEmpty());
      assertNull(McpDeliveryFraming.continuationLine("Vince Kaminski", "risk", Map.of()));
    }

    @Test
    @DisplayName("vocabulary is ordered by matched-document count, so the top entity wins the slot")
    void vocabularyOrderedByCount() {
      Map<String, Long> persons = new LinkedHashMap<>();
      persons.put("Ann Rare", 1L);
      persons.put("Bob Common", 90L);
      Map<String, Long> vocab = McpDeliveryFraming.entityVocabulary(facets(persons));
      assertEquals(List.of("Bob Common", "Ann Rare"), List.copyOf(vocab.keySet()));
      String line =
          McpDeliveryFraming.continuationLine("Ann Rare and Bob Common met", "meeting", vocab);
      assertTrue(line.contains("Bob Common"), line);
    }

    @Test
    @DisplayName("entity matching is case-insensitive against the delivered text")
    void caseInsensitiveMatch() {
      Map<String, Long> vocab =
          McpDeliveryFraming.entityVocabulary(facets(Map.of("Vince Kaminski", 3L)));
      assertNotNull(
          McpDeliveryFraming.continuationLine("cc: VINCE KAMINSKI", "who was copied", vocab));
    }

    @Test
    @DisplayName("blank or too-short entity values never produce a line")
    void shortEntitiesIgnored() {
      Map<String, Long> vocab = McpDeliveryFraming.entityVocabulary(facets(Map.of("Li", 40L)));
      assertNull(McpDeliveryFraming.continuationLine("Li was there", "who", vocab));
      assertNull(McpDeliveryFraming.continuationLine("", "who", vocab));
    }
  }

  @Nested
  @DisplayName("F2 — evidence, not answer")
  final class EvidenceNotAnswer {

    @Test
    @DisplayName("search header names the matched terms and denies being an answer")
    void searchHeaderNamesTerms() {
      String header = McpDeliveryFraming.searchEvidenceHeader(37, List.of("kaminski", "hedging"));
      assertTrue(header.startsWith("Retrieval evidence — 37 documents match"), header);
      assertTrue(header.contains("\"kaminski\", \"hedging\""), header);
      assertTrue(header.contains("not verified answers to your question"), header);
    }

    @Test
    @DisplayName("singular/plural agreement, and a term-less header still carries the framing")
    void headerDegradesCleanly() {
      String one = McpDeliveryFraming.searchEvidenceHeader(1, List.of());
      assertTrue(one.contains("1 document matches"), one);
      assertFalse(one.contains(" on "), one);
      assertTrue(one.contains("not verified answers"), one);
    }

    @Test
    @DisplayName("answer header frames the pack as evidence that may be relevant without answering")
    void answerHeaderFramesEvidence() {
      String header = McpDeliveryFraming.answerEvidenceHeader(3, 2);
      assertTrue(header.startsWith("Retrieval evidence — 3 passages from 2 documents"), header);
      assertTrue(header.contains("not a verified answer to your question"), header);
      assertTrue(header.contains("may be relevant without containing the answer"), header);
    }

    @Test
    @DisplayName("response matched terms dedupe case-insensitively in rank order and honour the cap")
    void responseMatchedTermsDedupeAndCap() {
      List<McpSearchResponseContent.HitContent> hits =
          List.of(
              hit(1, List.of("Kaminski", "hedging")),
              hit(2, List.of("kaminski", "memo")),
              hit(3, List.of("swap")));
      assertEquals(
          List.of("Kaminski", "hedging", "memo"), McpDeliveryFraming.responseMatchedTerms(hits, 3));
      assertEquals(List.of("Kaminski"), McpDeliveryFraming.responseMatchedTerms(hits, 1));
    }
  }

  @Nested
  @DisplayName("F3 — calibrated absence")
  final class CalibratedAbsence {

    @Test
    @DisplayName("zero results carry coverage, what was searched, and absence-is-not-evidence")
    void zeroResultsCarryFullFraming() {
      String note = McpDeliveryFraming.absenceNote(0, 0, 10_432L, "quarterly hedging policy", 400);
      assertNotNull(note);
      assertTrue(note.contains("10432 documents are indexed and were searched"), note);
      assertTrue(note.contains("\"quarterly hedging policy\""), note);
      assertTrue(note.contains("No document matched."), note);
      assertTrue(note.contains("Absence of results is not evidence of absence"), note);
      assertTrue(note.contains("alternate phrasings"), note);
      assertTrue(note.contains("native file tools"), note);
    }

    @Test
    @DisplayName("a non-empty but thin delivery trips the floor and names the measured size")
    void thinResultTripsFloor() {
      String note = McpDeliveryFraming.absenceNote(5, 120, 900L, "widget torque", 400);
      assertNotNull(note);
      assertTrue(note.contains("120 bytes, under the 400-byte floor"), note);
      assertTrue(note.contains("Absence of results is not evidence of absence"), note);
    }

    @Test
    @DisplayName("a substantive delivery at or above the floor is not framed at all")
    void substantiveDeliveryNotFramed() {
      assertNull(McpDeliveryFraming.absenceNote(5, 400, 900L, "widget torque", 400));
      assertNull(McpDeliveryFraming.absenceNote(5, 5_000, 900L, "widget torque", 400));
    }

    @Test
    @DisplayName("an unavailable doc count omits the coverage clause rather than guessing a number")
    void unavailableDocCountOmitsCoverage() {
      String note = McpDeliveryFraming.absenceNote(0, 0, -1L, "widget torque", 400);
      assertNotNull(note);
      assertTrue(note.contains("The index was searched for \"widget torque\""), note);
      assertFalse(note.contains("-1"), note);
      assertTrue(note.contains("Absence of results is not evidence of absence"), note);
    }

    @Test
    @DisplayName("delivered-body bytes measure hit text only — response scaffolding cannot lift it")
    void deliveredBodyBytesMeasuresHitTextOnly() {
      assertEquals(0, McpDeliveryFraming.deliveredBodyBytes(List.of()));
      assertEquals(0, McpDeliveryFraming.deliveredBodyBytes(null));
      McpSearchResponseContent.HitContent h =
          new McpSearchResponseContent.HitContent(
              1, "ab", "cde", 1.0, "fghi", List.of("jk"), List.of("content_preview"), null, null);
      // title 2 + path 3 + preview 4 + matched term 2 = 11; matchedFields are not delivered text.
      assertEquals(11, McpDeliveryFraming.deliveredBodyBytes(List.of(h)));
    }

    @Test
    @DisplayName("the thin measure ignores continuation lines, so F1 cannot suppress F3")
    void continuationDoesNotInflateBodyBytes() {
      McpSearchResponseContent.HitContent without =
          new McpSearchResponseContent.HitContent(
              1, "ab", "cde", 1.0, "fghi", List.of("jk"), List.of(), null, null);
      McpSearchResponseContent.HitContent with =
          new McpSearchResponseContent.HitContent(
              1,
              "ab",
              "cde",
              1.0,
              "fghi",
              List.of("jk"),
              List.of(),
              "note: this excerpt names \"Someone\" — a very long continuation line indeed.",
              null);
      assertEquals(
          McpDeliveryFraming.deliveredBodyBytes(List.of(without)),
          McpDeliveryFraming.deliveredBodyBytes(List.of(with)));
    }
  }

  @Nested
  @DisplayName("Settings")
  final class SettingsBehaviour {

    @Test
    @DisplayName("OFF is the default and reports no framing active")
    void offIsDefault() {
      assertFalse(McpDeliveryFraming.Settings.OFF.continuationEnabled());
      assertFalse(McpDeliveryFraming.Settings.OFF.evidenceNotAnswerEnabled());
      assertFalse(McpDeliveryFraming.Settings.OFF.calibratedAbsenceEnabled());
    }

    @Test
    @DisplayName("an uninitialized config store resolves to OFF — nothing turns on by omission")
    void uninitializedStoreResolvesOff() {
      // Exercises the null-store branch production hits at early boot. The precondition is asserted
      // explicitly so that if a future ui test installs a global store, this fails naming the
      // changed precondition rather than silently testing a different branch.
      assertNull(
          io.justsearch.configuration.resolved.ConfigStore.globalOrNull(),
          "precondition: no global ConfigStore is installed in the ui test JVM");
      assertEquals(McpDeliveryFraming.Settings.OFF, McpDeliveryFraming.resolveSettings());
    }

    @Test
    @DisplayName("framings compose — any subset may be active at once")
    void framingsCompose() {
      McpDeliveryFraming.Settings both = new McpDeliveryFraming.Settings(true, false, true, 400);
      assertTrue(both.continuationEnabled());
      assertFalse(both.evidenceNotAnswerEnabled());
      assertTrue(both.calibratedAbsenceEnabled());
    }
  }

  private static McpSearchResponseContent.HitContent hit(int rank, List<String> matchedTerms) {
    return new McpSearchResponseContent.HitContent(
        rank, "t" + rank, "/p" + rank, 1.0, "preview", matchedTerms, List.of(), null, null);
  }
}
