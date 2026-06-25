package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tempdoc 577 §2.14 Root III (#18) — the text-provenance classifier + the result stamp. */
class OutputLineageTest {

  @Test
  void corpusReadersClassifyAsCorpusQuoted() {
    assertEquals(OutputLineage.CORPUS_QUOTED, OutputLineage.forOperationId("core.search-index"));
    assertEquals(OutputLineage.CORPUS_QUOTED, OutputLineage.forOperationId("core.browse-folders"));
  }

  @Test
  void everythingElseIsRuntime() {
    assertEquals(OutputLineage.RUNTIME, OutputLineage.forOperationId("core.file-operations"));
    assertEquals(OutputLineage.RUNTIME, OutputLineage.forOperationId("core.remember"));
    assertEquals(OutputLineage.RUNTIME, OutputLineage.forOperationId(null));
    assertEquals(OutputLineage.RUNTIME, OutputLineage.forOperationId("vendor.mcphost.something"));
  }

  @Test
  void wireTokensAreStableLowercaseHyphen() {
    assertEquals("corpus-quoted", OutputLineage.CORPUS_QUOTED.wireToken());
    assertEquals("runtime", OutputLineage.RUNTIME.wireToken());
    assertEquals("agent-authored", OutputLineage.AGENT_AUTHORED.wireToken());
  }

  @Test
  void withLineageStampsStructuredDataWithoutLosingExistingEntries() {
    OperationResult base =
        OperationResult.success("ok", java.util.Map.of("searchResults", java.util.List.of("a")));
    OperationResult stamped = base.withLineage(OutputLineage.CORPUS_QUOTED);
    assertEquals("corpus-quoted", stamped.structuredData().get("lineage"));
    assertTrue(
        stamped.structuredData().containsKey("searchResults"),
        "the stamp must preserve existing structuredData entries");
    assertEquals("ok", stamped.message());
    assertTrue(stamped.success());
  }
}
