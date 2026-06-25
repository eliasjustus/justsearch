package io.justsearch.app.services.registry.emitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.conversation.CoreWorkflowCatalog;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tempdoc 565 §26.C — the workflow → picker-wire projection (the ONE picker-wire authority). */
final class UIWorkflowEmitterTest {

  @SuppressWarnings("unchecked")
  @Test
  void projectsEachWorkflowToTheLeanPickerEntry() {
    List<Map<String, Object>> entries = UIWorkflowEmitter.project(CoreWorkflowCatalog.catalog());
    // The core catalog ships >= 2 workflows so the picker is non-trivial (the §26.C bar).
    assertTrue(entries.size() >= 2, "core catalog projects at least 2 workflows for the picker");

    Map<String, Object> brief =
        entries.stream()
            .filter(e -> "core.research-brief".equals(e.get("id")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("research-brief workflow was not projected"));
    assertEquals("workflow", brief.get("type"));
    assertEquals("USER", brief.get("audience"));
    Map<String, Object> presentation = (Map<String, Object>) brief.get("presentation");
    assertEquals("registry-workflow.research-brief.label", presentation.get("labelKey"));
    assertEquals(
        "registry-workflow.research-brief.description", presentation.get("descriptionKey"));

    // The two LlmStep nodes carry the llm kind — the >1-node count drives the §26.A/B segment render.
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) brief.get("nodes");
    assertEquals(2, nodes.size());
    assertEquals("think", nodes.get(0).get("nodeId"));
    assertEquals("llm", nodes.get(0).get("kind"));
    assertEquals("draft", nodes.get(1).get("nodeId"));
  }
}
