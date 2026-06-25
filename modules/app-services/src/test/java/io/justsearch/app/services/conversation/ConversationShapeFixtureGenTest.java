package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.justsearch.agent.api.registry.ConversationShape;
import io.justsearch.agent.api.registry.EventDescriptor;
import io.justsearch.agent.api.registry.EventField;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * tempdoc 564 — generates {@code scripts/codegen/shapes.fixture.json} from the Java
 * {@link CoreConversationShapeCatalog} so the FE shape-handler codegen consumes a <em>projection
 * of the catalog</em>, not a hand-maintained fixture.
 *
 * <p>Capture-or-verify (mirrors {@code SubstrateSchemaGenTest}): writes the file on first run or
 * when {@code -Dupdate.shapes.fixture=true}; otherwise asserts the committed file equals the
 * catalog projection. This retires the hand {@code BUNDLED_SHAPES} array + the fragile-regex
 * {@code ConversationShapeFixtureParityTest} — the fixture can no longer drift because it <em>is</em>
 * the catalog, projected (the 564 single-source → projection → gate principle applied to the
 * codegen fixture itself).
 *
 * <p>Uses a tiny deterministic JSON writer (no Jackson) so the test does not depend on the
 * repo's Jackson 2-vs-3 mapper configuration, and capture/verify compare byte-identical output.
 */
final class ConversationShapeFixtureGenTest {

  @Test
  @DisplayName("shapes.fixture.json is a projection of CoreConversationShapeCatalog")
  void fixtureMatchesCatalog() throws IOException {
    String currentJson = toJson(project(), 0) + "\n";
    Path path =
        resolveRepoRoot().resolve("scripts").resolve("codegen").resolve("shapes.fixture.json");

    boolean update = Boolean.getBoolean("update.shapes.fixture");
    if (update || !Files.exists(path)) {
      Files.createDirectories(path.getParent());
      Files.writeString(path, currentJson);
      if (!update) {
        fail("shapes.fixture.json captured at " + path + " — re-run to verify (expected first run).");
      }
      return;
    }

    String committed = Files.readString(path).replace("\r\n", "\n").strip();
    assertEquals(
        committed,
        currentJson.strip(),
        "shapes.fixture.json diverged from CoreConversationShapeCatalog at "
            + path
            + ". Run :modules:app-services:test with -Dupdate.shapes.fixture=true to recapture, then"
            + " regenerate FE handlers (node scripts/codegen/gen-shape-handlers.mjs).");
  }

  /** Explicit Map/List projection: [ {id, eventSchema:[{name, fields:[{...}]}]} ]. */
  private static List<Object> project() {
    List<Object> out = new ArrayList<>();
    for (ConversationShape shape : CoreConversationShapeCatalog.catalog().definitions()) {
      Map<String, Object> shapeMap = new LinkedHashMap<>();
      shapeMap.put("id", shape.id().value());
      List<Object> events = new ArrayList<>();
      for (EventDescriptor descriptor : shape.eventSchema()) {
        Map<String, Object> descriptorMap = new LinkedHashMap<>();
        descriptorMap.put("name", descriptor.name());
        List<Object> fields = new ArrayList<>();
        for (EventField field : descriptor.fields()) {
          Map<String, Object> fieldMap = new LinkedHashMap<>();
          fieldMap.put("name", field.name());
          fieldMap.put("type", field.type().name());
          fieldMap.put("optional", field.optional());
          fieldMap.put("enumValues", new ArrayList<Object>(field.enumValues()));
          fieldMap.put("elementType", field.elementType() == null ? null : field.elementType().name());
          fieldMap.put("objectType", field.objectType());
          fields.add(fieldMap);
        }
        descriptorMap.put("fields", fields);
        events.add(descriptorMap);
      }
      shapeMap.put("eventSchema", events);
      out.add(shapeMap);
    }
    return out;
  }

  // --- Minimal deterministic JSON writer (objects/arrays/string/boolean/null only) ---

  private static String toJson(Object value, int depth) {
    if (value == null) {
      return "null";
    }
    if (value instanceof String s) {
      return quote(s);
    }
    if (value instanceof Boolean b) {
      return b.toString();
    }
    String pad = "  ".repeat(depth);
    String padChild = "  ".repeat(depth + 1);
    if (value instanceof Map<?, ?> map) {
      if (map.isEmpty()) {
        return "{}";
      }
      StringBuilder sb = new StringBuilder("{\n");
      int i = 0;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        sb.append(padChild)
            .append(quote(entry.getKey().toString()))
            .append(": ")
            .append(toJson(entry.getValue(), depth + 1));
        sb.append(++i < map.size() ? ",\n" : "\n");
      }
      return sb.append(pad).append("}").toString();
    }
    if (value instanceof List<?> list) {
      if (list.isEmpty()) {
        return "[]";
      }
      StringBuilder sb = new StringBuilder("[\n");
      for (int i = 0; i < list.size(); i++) {
        sb.append(padChild).append(toJson(list.get(i), depth + 1));
        sb.append(i < list.size() - 1 ? ",\n" : "\n");
      }
      return sb.append(pad).append("]").toString();
    }
    throw new IllegalStateException("unsupported JSON value type: " + value.getClass());
  }

  private static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static Path resolveRepoRoot() {
    Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    while (cursor != null) {
      if (Files.exists(
          cursor.resolve("scripts").resolve("codegen").resolve("gen-shape-handlers.mjs"))) {
        return cursor;
      }
      cursor = cursor.getParent();
    }
    throw new IllegalStateException("repo root not found from " + System.getProperty("user.dir"));
  }
}
