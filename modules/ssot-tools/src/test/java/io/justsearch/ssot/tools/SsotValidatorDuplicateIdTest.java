package io.justsearch.ssot.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tempdoc 393 § C.2 — duplicate-id detection in SSOT catalogs.
 *
 * <p>Covers the {@link SsotValidator#findDuplicateIds} pure function used by both the field
 * catalog and the analyzers catalog validators.
 */
class SsotValidatorDuplicateIdTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  @Test
  void emptyArrayReturnsEmptySet() {
    ArrayNode arr = MAPPER.createArrayNode();
    assertTrue(SsotValidator.findDuplicateIds(arr).isEmpty());
  }

  @Test
  void nullInputReturnsEmptySet() {
    assertTrue(SsotValidator.findDuplicateIds(null).isEmpty());
  }

  @Test
  void uniqueIdsReturnEmptySet() {
    ArrayNode arr = MAPPER.createArrayNode();
    arr.add(entry("doc_id"));
    arr.add(entry("path"));
    arr.add(entry("content"));
    assertTrue(SsotValidator.findDuplicateIds(arr).isEmpty());
  }

  @Test
  void singleDuplicateIsDetected() {
    ArrayNode arr = MAPPER.createArrayNode();
    arr.add(entry("ner_status"));
    arr.add(entry("path"));
    arr.add(entry("ner_status")); // duplicate
    Set<String> dups = SsotValidator.findDuplicateIds(arr);
    assertEquals(Set.of("ner_status"), dups);
  }

  @Test
  void multipleDuplicatesReturnedInSortedOrder() {
    ArrayNode arr = MAPPER.createArrayNode();
    arr.add(entry("ner_status"));
    arr.add(entry("zebra"));
    arr.add(entry("ner_retry_count"));
    arr.add(entry("ner_status")); // dup
    arr.add(entry("apple"));
    arr.add(entry("ner_retry_count")); // dup
    arr.add(entry("zebra")); // dup
    Set<String> dups = SsotValidator.findDuplicateIds(arr);
    // TreeSet preserves lexicographic order
    assertEquals(Set.of("ner_retry_count", "ner_status", "zebra"), dups);
  }

  @Test
  void blankIdIsIgnored() {
    ArrayNode arr = MAPPER.createArrayNode();
    arr.add(entry(""));
    arr.add(entry(""));
    arr.add(entry("real"));
    // Empty ids must not be counted as duplicates of each other.
    assertTrue(SsotValidator.findDuplicateIds(arr).isEmpty());
  }

  @Test
  void entryWithoutIdFieldIsIgnored() {
    ArrayNode arr = MAPPER.createArrayNode();
    arr.add(MAPPER.createObjectNode().put("name", "something"));
    arr.add(entry("real"));
    assertTrue(SsotValidator.findDuplicateIds(arr).isEmpty());
  }

  @Test
  void nonArrayInputReturnsEmptySet() {
    assertTrue(SsotValidator.findDuplicateIds(MAPPER.createObjectNode()).isEmpty());
  }

  private static ObjectNode entry(String id) {
    return MAPPER.createObjectNode().put("id", id);
  }
}
