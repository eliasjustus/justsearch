package io.justsearch.app.api.selection;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelectionPayloadJsonTest {

  private ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = JsonMapper.builder().build();
  }

  @Test
  void textRangeWithCanonicalAddress_roundTrip() throws Exception {
    SelectionPayload original =
        new SelectionPayload.TextRange(
            new DocumentAddress.Canonical("doc-1", 100, 250),
            "Lorem ipsum dolor sit amet.",
            new SelectionPayload.HostEntity("doc", "doc-1"));

    String json = mapper.writeValueAsString(original);
    assertTrue(json.contains("\"kind\":\"text-range\""), "missing payload kind: " + json);
    assertTrue(json.contains("\"coords\":\"canonical\""), "missing address coords: " + json);

    SelectionPayload decoded = mapper.readValue(json, SelectionPayload.class);
    assertEquals(original, decoded);
    assertInstanceOf(SelectionPayload.TextRange.class, decoded);
    SelectionPayload.TextRange tr = (SelectionPayload.TextRange) decoded;
    assertInstanceOf(DocumentAddress.Canonical.class, tr.address());
  }

  @Test
  void textRangeWithDisplayAddress_roundTrip() throws Exception {
    SelectionPayload original =
        new SelectionPayload.TextRange(
            new DocumentAddress.Display(
                "doc-1", "preview-5k", 50, 120, new DocumentAddress.CanonicalHint(50, 120)),
            "Some preview slice",
            new SelectionPayload.HostEntity("doc", "doc-1"));

    String json = mapper.writeValueAsString(original);
    assertTrue(json.contains("\"coords\":\"display\""), json);
    assertTrue(json.contains("\"viewId\":\"preview-5k\""), json);

    SelectionPayload decoded = mapper.readValue(json, SelectionPayload.class);
    assertEquals(original, decoded);
  }

  @Test
  void textRangeWithDisplayAddress_noHint_roundTrip() throws Exception {
    SelectionPayload original =
        new SelectionPayload.TextRange(
            new DocumentAddress.Display("doc-1", "preview-5k", 50, 120, null),
            "slice without hint",
            new SelectionPayload.HostEntity("doc", "doc-1"));
    String json = mapper.writeValueAsString(original);
    // JsonInclude.NON_NULL: canonicalHint absent in JSON
    assertFalse(json.contains("canonicalHint"), "expected omitted hint: " + json);
    SelectionPayload decoded = mapper.readValue(json, SelectionPayload.class);
    assertEquals(original, decoded);
  }

  @Test
  void canonicalRejectsInvertedRange() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DocumentAddress.Canonical("doc-1", 200, 100));
  }

  @Test
  void displayRejectsNegativeStart() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new DocumentAddress.Display("doc-1", "preview-5k", -1, 10, null));
  }

  @Test
  void composeIntentRoundTrip_withSelection() throws Exception {
    ComposeIntent original =
        new ComposeIntent(
            new SelectionPayload.TextRange(
                new DocumentAddress.Canonical("doc-1", 0, 50),
                "slice",
                new SelectionPayload.HostEntity("doc", "doc-1")),
            "core.summarize",
            "focus on the key idea",
            "BUTTON");

    String json = mapper.writeValueAsString(original);
    ComposeIntent decoded = mapper.readValue(json, ComposeIntent.class);
    assertEquals(original, decoded);
  }

  @Test
  void composeIntentRoundTrip_noSelection_noUserPrompt() throws Exception {
    ComposeIntent original = new ComposeIntent(null, "core.ask", null, "BUTTON");
    String json = mapper.writeValueAsString(original);
    assertFalse(json.contains("selection"), json);
    assertFalse(json.contains("userPrompt"), json);
    ComposeIntent decoded = mapper.readValue(json, ComposeIntent.class);
    assertEquals(original, decoded);
  }

  @Test
  void composeIntentRecordBuilder_compiles() {
    // Sanity that @RecordBuilder generated builder for ComposeIntent.
    ComposeIntent ci =
        ComposeIntentBuilder.builder().operation("core.ask").source("BUTTON").build();
    assertEquals("core.ask", ci.operation());
    assertNull(ci.selection());
    assertNull(ci.userPrompt());
  }
}
