package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("KnowledgeHttpApiAdapter LLM expansion: mergeExpansion validation")
final class KnowledgeHttpApiAdapterExpansionTest {

  @Test
  @DisplayName("null expansion text returns null")
  void mergeExpansion_returnsNullWhenNull() {
    assertNull(KnowledgeSearchEngine.mergeExpansion("optimize", null));
  }

  @Test
  @DisplayName("blank expansion text returns null")
  void mergeExpansion_returnsNullWhenBlank() {
    assertNull(KnowledgeSearchEngine.mergeExpansion("optimize", "   "));
  }

  @Test
  @DisplayName("valid expansion adds morphological variants")
  void mergeExpansion_addsNewVariants() {
    String result = KnowledgeSearchEngine.mergeExpansion("optimize", "optimized optimizing optimization");
    assertNotNull(result);
    assertTrue(result.contains("optimize"), "original term preserved");
    assertTrue(result.contains("optimized"), "past tense added");
    assertTrue(result.contains("optimizing"), "gerund added");
    assertTrue(result.contains("optimization"), "nominalization added");
  }

  @Test
  @DisplayName("expansion terms already present in original are deduplicated")
  void mergeExpansion_deduplicatesAlreadyPresent() {
    // "optimize" is already in the original; expansion only repeats it
    String result = KnowledgeSearchEngine.mergeExpansion("optimize", "optimize");
    assertNull(result, "no new terms added → null");
  }

  @Test
  @DisplayName("case-insensitive deduplication")
  void mergeExpansion_deduplicatesCaseInsensitive() {
    // "Optimize" is the same root as "optimize" in original
    String result = KnowledgeSearchEngine.mergeExpansion("optimize", "Optimize");
    assertNull(result, "case-insensitive duplicate → null");
  }

  @Test
  @DisplayName("expansion token with digit is rejected")
  void mergeExpansion_rejectsTokenWithDigit() {
    assertNull(KnowledgeSearchEngine.mergeExpansion("optimize", "optimized123"));
  }

  @Test
  @DisplayName("expansion token with punctuation is rejected")
  void mergeExpansion_rejectsTokenWithPunctuation() {
    assertNull(KnowledgeSearchEngine.mergeExpansion("optimize", "optimized!"));
  }

  @Test
  @DisplayName("expansion exceeding 3x original token count is truncated, not rejected")
  void mergeExpansion_truncatesExcessTokens() {
    // Original: "optimize" (1 token). 3× = 3, so 4th token ("optimizes") is truncated.
    String result =
        KnowledgeSearchEngine.mergeExpansion(
            "optimize", "optimized optimizing optimization optimizes");
    assertNotNull(result, "truncated expansion should be accepted");
    assertTrue(result.contains("optimized"), "first token within limit present");
    assertTrue(result.contains("optimizing"), "second token within limit present");
    assertTrue(result.contains("optimization"), "third token within limit present");
    assertFalse(result.contains("optimizes"), "fourth token beyond limit not included");
  }

  @Test
  @DisplayName("expansion exactly at 3x original token count is accepted")
  void mergeExpansion_acceptsExactlyThreeXTokenCount() {
    // Original: "optimize" (1 token). 3× = 3 tokens → accepted.
    String result =
        KnowledgeSearchEngine.mergeExpansion("optimize", "optimized optimizing optimization");
    assertNotNull(result);
  }

  @Test
  @DisplayName("multi-word original: expansion limit scales with original length")
  void mergeExpansion_multiWordOriginalScalesLimit() {
    // Original: "optimize performance" (2 tokens). 3× = 6 tokens → accepted.
    String result =
        KnowledgeSearchEngine.mergeExpansion(
            "optimize performance",
            "optimized optimizing optimization performances performed performing");
    assertNotNull(result);
    assertTrue(result.startsWith("optimize performance"));
  }

  @Test
  @DisplayName("nothing new returns null")
  void mergeExpansion_returnsNullWhenNothingNew() {
    String result = KnowledgeSearchEngine.mergeExpansion("optimize performance", "optimize performance");
    assertNull(result, "all expansion terms already in original → null");
  }

  @Test
  @DisplayName("expansion terms receive boost attenuation suffix")
  void mergeExpansion_appliesBoostAttenuation() {
    String result = KnowledgeSearchEngine.mergeExpansion("optimize", "optimized optimizing");
    assertNotNull(result);
    assertTrue(result.contains("optimized^0.3"), "expansion term has ^0.3 boost");
    assertTrue(result.contains("optimizing^0.3"), "expansion term has ^0.3 boost");
    assertFalse(result.startsWith("optimize^"), "original term should NOT be boosted");
  }

}
